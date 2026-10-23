/*
	Copyright(c) 2021-2026 jvde.github@gmail.com

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

// Opt-in: define SWAR_ENABLE_SIMD to compile the 16-byte SSE2/NEON fast path.
// Default is the scalar size_t SWAR, which benchmarks equivalently on short
// NMEA lines and avoids platform-specific intrinsics in hot header code.
#if defined(SWAR_ENABLE_SIMD)
	#if defined(__SSE2__)
		#include <emmintrin.h>
		#define SWAR_HAS_SIMD 1
	#elif defined(__aarch64__)
		#include <arm_neon.h>
		#define SWAR_HAS_SIMD 1
	#endif
#endif

// Software-Within-A-Register byte scanning primitives. Scalar path uses size_t
// (8-byte word). On SSE2/aarch64 a 16-byte SIMD path scans twice as many bytes
// per iteration before falling through to the scalar path for the 8..15 byte
// remainder. Callers write their match expression once in terms of `word`;
// overloaded has_byte/has_byte_lt resolve to the right type in each block.

namespace SWAR
{
	static constexpr size_t ONES = ~(size_t)0 / 255;
	static constexpr size_t HIGHS = ONES * 128;

	static constexpr size_t mask(char target)
	{
		return ONES * (unsigned char)target;
	}

	// Scalar (size_t) overloads.
	static inline size_t has_byte(size_t word, size_t m)
	{
		size_t v = word ^ m;
		return (v - ONES) & ~v & HIGHS;
	}

	static inline size_t has_byte_lt(size_t word, size_t n)
	{
		return (word - ONES * n) & ~word & HIGHS;
	}

	static inline bool any_set(size_t m) { return m != 0; }

	static inline int first_byte(size_t m)
	{
#if defined(__GNUC__) || defined(__clang__)
		if (sizeof(size_t) == 8)
			return __builtin_ctzll((unsigned long long)m) >> 3;
		else
			return __builtin_ctz((unsigned int)m) >> 3;
#else
		int n = 0;
		while (!(m & 0xFF))
		{
			m >>= 8;
			n++;
		}
		return n;
#endif
	}

	static inline int first_byte_pos(size_t m) { return first_byte(m); }

#if defined(SWAR_HAS_SIMD) && defined(__SSE2__)
	using v128 = __m128i;

	static inline v128 load16(const char *p)
	{
		return _mm_loadu_si128(reinterpret_cast<const __m128i *>(p));
	}

	static inline v128 has_byte(v128 w, size_t m)
	{
		return _mm_cmpeq_epi8(w, _mm_set1_epi8(static_cast<char>(m)));
	}

	// Unsigned v < n via sign-bias: (v ^ 0x80) < (n ^ 0x80) as signed int8.
	static inline v128 has_byte_lt(v128 w, size_t n)
	{
		v128 bias = _mm_set1_epi8(static_cast<char>(0x80));
		v128 t = _mm_set1_epi8(static_cast<char>(n ^ 0x80));
		return _mm_cmpgt_epi8(t, _mm_xor_si128(w, bias));
	}

	static inline bool any_set(v128 m)
	{
		return _mm_movemask_epi8(m) != 0;
	}

	static inline int first_byte(v128 m)
	{
		return __builtin_ctz(static_cast<unsigned>(_mm_movemask_epi8(m)));
	}

#elif defined(SWAR_HAS_SIMD) && defined(__aarch64__)
	using v128 = uint8x16_t;

	static inline v128 load16(const char *p)
	{
		return vld1q_u8(reinterpret_cast<const uint8_t *>(p));
	}

	static inline v128 has_byte(v128 w, size_t m)
	{
		return vceqq_u8(w, vdupq_n_u8(static_cast<uint8_t>(m)));
	}

	static inline v128 has_byte_lt(v128 w, size_t n)
	{
		return vcltq_u8(w, vdupq_n_u8(static_cast<uint8_t>(n)));
	}

	static inline bool any_set(v128 m)
	{
		return vmaxvq_u8(m) != 0;
	}

	// shrn trick: compress 16 lanes of 0xFF/0x00 into 64-bit (4 bits/lane).
	static inline int first_byte(v128 m)
	{
		uint8x8_t n = vshrn_n_u16(vreinterpretq_u16_u8(m), 4);
		uint64_t v = vget_lane_u64(vreinterpret_u64_u8(n), 0);
		return __builtin_ctzll(v) >> 2;
	}
#endif
}

// Scan (buf + i) .. (buf + limit) for any byte matching `expr`. On SIMD targets
// a 16-byte fast path runs first, then a scalar 8-byte tail, then the caller
// handles the final <8 bytes byte-by-byte. `expr` uses `word`; overloaded
// has_byte/has_byte_lt resolve separately in each block.
#ifdef SWAR_HAS_SIMD
#define SWAR_SKIP_AT(buf, i, limit, expr)                            \
	do                                                               \
	{                                                                \
		bool _swar_found = false;                                    \
		while (!_swar_found && (i) + 16 <= (limit))                  \
		{                                                            \
			SWAR::v128 word = SWAR::load16((buf) + (i));             \
			auto _hit = (expr);                                      \
			if (SWAR::any_set(_hit))                                 \
			{                                                        \
				(i) += SWAR::first_byte(_hit);                       \
				_swar_found = true;                                  \
				break;                                               \
			}                                                        \
			(i) += 16;                                               \
		}                                                            \
		while (!_swar_found && (i) + (int)sizeof(size_t) <= (limit)) \
		{                                                            \
			size_t word;                                             \
			std::memcpy(&word, (buf) + (i), sizeof(word));           \
			auto _hit = (expr);                                      \
			if (SWAR::any_set(_hit))                                 \
			{                                                        \
				(i) += SWAR::first_byte(_hit);                       \
				break;                                               \
			}                                                        \
			(i) += sizeof(size_t);                                   \
		}                                                            \
	} while (0)

#define SWAR_SKIP_PTR(p, pend, expr)                           \
	do                                                         \
	{                                                          \
		bool _swar_found = false;                              \
		while (!_swar_found && (p) + 16 <= (pend))             \
		{                                                      \
			SWAR::v128 word = SWAR::load16(p);                 \
			auto _hit = (expr);                                \
			if (SWAR::any_set(_hit))                           \
			{                                                  \
				(p) += SWAR::first_byte(_hit);                 \
				_swar_found = true;                            \
				break;                                         \
			}                                                  \
			(p) += 16;                                         \
		}                                                      \
		while (!_swar_found && (p) + sizeof(size_t) <= (pend)) \
		{                                                      \
			size_t word;                                       \
			std::memcpy(&word, (p), sizeof(word));             \
			auto _hit = (expr);                                \
			if (SWAR::any_set(_hit))                           \
			{                                                  \
				(p) += SWAR::first_byte(_hit);                 \
				break;                                         \
			}                                                  \
			(p) += sizeof(size_t);                             \
		}                                                      \
	} while (0)

#else
#define SWAR_SKIP_AT(buf, i, limit, expr)              \
	while ((i) + (int)sizeof(size_t) <= (limit))       \
	{                                                  \
		size_t word;                                   \
		std::memcpy(&word, (buf) + (i), sizeof(word)); \
		size_t _swar_hit = (expr);                     \
		if (_swar_hit)                                 \
		{                                              \
			(i) += SWAR::first_byte_pos(_swar_hit);    \
			break;                                     \
		}                                              \
		(i) += sizeof(size_t);                         \
	}

#define SWAR_SKIP_PTR(p, pend, expr)                \
	while ((p) + sizeof(size_t) <= (pend))          \
	{                                               \
		size_t word;                                \
		std::memcpy(&word, (p), sizeof(word));      \
		size_t _swar_hit = (expr);                  \
		if (_swar_hit)                              \
		{                                           \
			(p) += SWAR::first_byte_pos(_swar_hit); \
			break;                                  \
		}                                           \
		(p) += sizeof(size_t);                      \
	}
#endif
