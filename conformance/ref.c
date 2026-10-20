#include <glib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <stdint.h>
#define DATA_TRANSF_BLOCK_BYTES 0x2000
#define ELEKTRON_SAMPLE_RATE 48000
#define ELEKTRON_SAMPLE_INFO_PAD_I32_LEN 10
#define ELEKTRON_LOOP_TYPE_FWD 0
#define ELEKTRON_LOOP_TYPE_NO 0x7f
struct sample_info { guint32 loop_start, loop_end, loop_type, channels; };
struct elektron_sample_header { guint8 type; guint8 stereo; guint8 rsvd0[2]; guint32 size; guint32 rate; guint32 loop_start; guint32 loop_end; guint8 loop_type; guint8 rsvd1[3]; guint32 padding[ELEKTRON_SAMPLE_INFO_PAD_I32_LEN]; };
static void free_msg(GByteArray *m){ g_byte_array_free(m, TRUE); }
static gchar *elektron_get_cp1252(const gchar *i){ return g_strdup(i); }
static const guint8 MSG_HEADER[] = { 0xf0, 0, 0x20, 0x3c, 0x10, 0 };
static const guint8 FS_SAMPLE_OPEN_FILE_WRITER_REQUEST[] = { 0x40, 0, 0, 0, 0 };
static const guint8 FS_SAMPLE_CLOSE_FILE_WRITER_REQUEST[] = { 0x41, 0, 0, 0, 0, 0, 0, 0, 0 };
static const guint8 FS_SAMPLE_WRITE_FILE_REQUEST[] = { 0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
static GByteArray *
elektron_decode_payload (const GByteArray *src)
{
  GByteArray *dst;
  int i, j, k, dst_len;
  unsigned int shift;

  dst_len = src->len - ceill (src->len / 8.0);
  dst = g_byte_array_sized_new (dst_len);
  dst->len = dst_len;

  for (i = 0, j = 0; i < src->len; i += 8, j += 7)
    {
      shift = 0x40;
      for (k = 0; k < 7 && i + k + 1 < src->len; k++)
	{
	  dst->data[j + k] =
	    src->data[i + k + 1] | (src->data[i] & shift ? 0x80 : 0);
	  shift = shift >> 1;
	}
    }

  return dst;
}

static GByteArray *
elektron_encode_payload (const GByteArray *src)
{
  GByteArray *dst;
  int i, j, k, dst_len;
  unsigned int accum;

  dst_len = src->len + ceill (src->len / 7.0);
  dst = g_byte_array_sized_new (dst_len);
  dst->len = dst_len;

  for (i = 0, j = 0; j < src->len; i += 8, j += 7)
    {
      accum = 0;
      for (k = 0; k < 7; k++)
	{
	  accum = accum << 1;
	  if (j + k < src->len)
	    {
	      if (src->data[j + k] & 0x80)
		{
		  accum |= 1;
		}
	      dst->data[i + k + 1] = src->data[j + k] & 0x7f;
	    }
	}
      dst->data[i] = accum;
    }

  return dst;
}

static GByteArray *
elektron_msg_to_raw (const GByteArray *msg)
{
  GByteArray *encoded = elektron_encode_payload (msg);
  guint total = sizeof (MSG_HEADER) + encoded->len + 1;
  GByteArray *raw = g_byte_array_sized_new (total);

  g_byte_array_append (raw, MSG_HEADER, sizeof (MSG_HEADER));
  g_byte_array_append (raw, encoded->data, encoded->len);
  g_byte_array_append (raw, (guint8 *) "\xf7", 1);
  free_msg (encoded);

  return raw;
}
static GByteArray *
elektron_new_msg (const guint8 *data, guint len)
{
  GByteArray *msg = g_byte_array_new ();

  g_byte_array_append (msg, (guchar *) "\0\0\0\0", 4);
  g_byte_array_append (msg, data, len);

  return msg;
}

static GByteArray *
elektron_new_msg_uint8 (const guint8 *data, guint len, guint8 type)
{
  GByteArray *msg = elektron_new_msg (data, len);

  g_byte_array_append (msg, &type, 1);

  return msg;
}

static GByteArray *
elektron_new_msg_path (const guint8 *data, guint len, const gchar *path)
{
  GByteArray *msg;
  gchar *path_cp1252 = elektron_get_cp1252 (path);

  msg = elektron_new_msg (data, len);
  g_byte_array_append (msg, (guchar *) path_cp1252, strlen (path_cp1252) + 1);
  g_free (path_cp1252);

  return msg;
}
static GByteArray *
elektron_new_msg_open_common_write (const guint8 *data, guint len,
				    const gchar *path, guint bytes)
{
  guint32 aux32;
  GByteArray *msg = elektron_new_msg_path (data, len, path);

  aux32 = g_htonl (bytes);
  memcpy (&msg->data[5], &aux32, sizeof (guint32));

  return msg;
}

static GByteArray *
elektron_new_msg_open_sample_write (const gchar *path, guint bytes)
{
  return
    elektron_new_msg_open_common_write (FS_SAMPLE_OPEN_FILE_WRITER_REQUEST,
					sizeof
					(FS_SAMPLE_OPEN_FILE_WRITER_REQUEST),
					path,
					bytes +
					sizeof (struct
						elektron_sample_header));
}
static GByteArray *
elektron_new_msg_write_sample_blk (guint id, GByteArray *sample,
				   guint *total, guint seq, void *data)
{
  guint32 aux32;
  guint16 aux16, *aux16p;
  int i, consumed, bytes_blk;
  struct sample_info *sample_info = data;
  struct elektron_sample_header elektron_sample_header;
  GByteArray *msg = elektron_new_msg (FS_SAMPLE_WRITE_FILE_REQUEST,
				      sizeof (FS_SAMPLE_WRITE_FILE_REQUEST));


  aux32 = g_htonl (id);
  memcpy (&msg->data[5], &aux32, sizeof (guint32));
  aux32 = g_htonl (DATA_TRANSF_BLOCK_BYTES * seq);
  memcpy (&msg->data[13], &aux32, sizeof (guint32));

  bytes_blk = DATA_TRANSF_BLOCK_BYTES;
  consumed = 0;

  if (seq == 0)
    {
      //See comment in elektron_sample_header struct.
      guint8 loop_type = sample_info->loop_type ? ELEKTRON_LOOP_TYPE_NO :
	ELEKTRON_LOOP_TYPE_FWD;
      elektron_sample_header.type = 0;
      elektron_sample_header.stereo = sample_info->channels - 1;
      memset (&elektron_sample_header.rsvd0, 0, 2);
      elektron_sample_header.size = g_htonl (sample->len);
      elektron_sample_header.rate = g_htonl (ELEKTRON_SAMPLE_RATE);
      elektron_sample_header.loop_start = g_htonl (sample_info->loop_start);
      elektron_sample_header.loop_end = g_htonl (sample_info->loop_end);
      elektron_sample_header.loop_type = loop_type;
      memset (&elektron_sample_header.rsvd1, 0, 3);
      memset (&elektron_sample_header.padding, 0,
	      sizeof (guint32) * ELEKTRON_SAMPLE_INFO_PAD_I32_LEN);

      g_byte_array_append (msg, (guchar *) & elektron_sample_header,
			   sizeof (struct elektron_sample_header));

      consumed = sizeof (struct elektron_sample_header);
      bytes_blk -= consumed;
    }

  i = 0;
  aux16p = (guint16 *) & sample->data[*total];
  while (i < bytes_blk && *total < sample->len)
    {
      aux16 = g_htons (*aux16p);
      g_byte_array_append (msg, (guint8 *) & aux16, sizeof (guint16));
      aux16p++;
      (*total) += sizeof (guint16);
      consumed += sizeof (guint16);
      i += sizeof (guint16);
    }

  aux32 = g_htonl (consumed);
  memcpy (&msg->data[9], &aux32, sizeof (guint32));

  return msg;
}
static GByteArray *
elektron_new_msg_close_common_write (const guint8 *data, guint len,
				     guint id, guint bytes)
{
  guint32 aux32;
  GByteArray *msg = elektron_new_msg (data, len);

  aux32 = g_htonl (id);
  memcpy (&msg->data[5], &aux32, sizeof (guint32));
  aux32 = g_htonl (bytes);
  memcpy (&msg->data[9], &aux32, sizeof (guint32));

  return msg;
}

static GByteArray *
elektron_new_msg_close_sample_write (guint id, guint bytes)
{
  return
    elektron_new_msg_close_common_write (FS_SAMPLE_CLOSE_FILE_WRITER_REQUEST,
					 sizeof
					 (FS_SAMPLE_CLOSE_FILE_WRITER_REQUEST),
					 id,
					 bytes +
					 sizeof (struct
						 elektron_sample_header));
}
static guint16 seq=0;
static void emit(GByteArray *msg){ guint16 a=g_htons(seq); seq++; memcpy(msg->data,&a,2); GByteArray *r=elektron_msg_to_raw(msg); for(guint i=0;i<r->len;i++) printf("%02x",r->data[i]); printf("\n"); free_msg(r); free_msg(msg);}
int main(){
  int N=10000; GByteArray *s=g_byte_array_sized_new(N*2); s->len=N*2;
  for(int i=0;i<N;i++){ gint16 v=(gint16)((i*37)%65536-32768); memcpy(&s->data[i*2],&v,2);} // host-endian, like elektroid's loaded sample
  struct sample_info si={N-1,N-1,0,1};
  emit(elektron_new_msg_open_sample_write("/test/abc", s->len));
  guint total=0, k=0; guint32 id=0x12345678;
  while(total<s->len){ emit(elektron_new_msg_write_sample_blk(id,s,&total,k,&si)); k++; }
  emit(elektron_new_msg_close_sample_write(id,total));
  return 0; }
